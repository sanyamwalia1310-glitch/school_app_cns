"""Server-only Firebase Authentication provisioning for OTP-completed account actions."""

from __future__ import annotations

import json
from pathlib import Path

from flask import current_app


class FirebaseAuthProvisioningError(RuntimeError):
    """Raised when Flask cannot safely create, map, or update a Firebase Auth user."""


def _firebase_auth():
    """Lazily initialise Firebase Admin using ADC or an explicitly configured server key file."""
    try:
        import firebase_admin
        from firebase_admin import auth, credentials
    except ImportError as error:
        raise FirebaseAuthProvisioningError(
            "Firebase Admin SDK is not installed on the server."
        ) from error

    try:
        firebase_admin.get_app()
    except ValueError:
        key_path = current_app.config["FIREBASE_SERVICE_ACCOUNT_PATH"]
        key_json = current_app.config["FIREBASE_SERVICE_ACCOUNT_JSON"]
        credential = None
        if key_json:
            try:
                credential = credentials.Certificate(json.loads(key_json))
            except (TypeError, ValueError) as error:
                raise FirebaseAuthProvisioningError(
                    "Firebase Admin service-account JSON is invalid on this server."
                ) from error
        elif key_path:
            if not Path(key_path).is_file():
                raise FirebaseAuthProvisioningError(
                    "Firebase Admin service-account file is not available on this server."
                )
            credential = credentials.Certificate(key_path)
        options = {}
        if current_app.config["FIREBASE_PROJECT_ID"]:
            options["projectId"] = current_app.config["FIREBASE_PROJECT_ID"]
        try:
            firebase_admin.initialize_app(credential=credential, options=options or None)
        except Exception as error:  # Credentials errors must not disclose implementation details to clients.
            raise FirebaseAuthProvisioningError(
                "Firebase Admin is not configured correctly on the server."
            ) from error
    return auth


def _firebase_email(user) -> str:
    username = (user["username"] or "").strip().lower()
    domain = current_app.config["FIREBASE_AUTH_EMAIL_DOMAIN"].strip().lower()
    if not username or not domain or "@" in username or "@" in domain:
        raise FirebaseAuthProvisioningError("The Firebase account mapping is not configured correctly.")
    return f"{username}@{domain}"


def provision_firebase_password(user, new_password: str, *, allow_create: bool) -> str:
    """Create/update the Firebase email/password account and return its stable UID.

    A Flask account with an existing UID is never silently re-linked. Legacy records without a
    UID may link only to their deterministic, server-owned school-ID email address.
    """
    auth = _firebase_auth()
    firebase_uid = (user["firebase_uid"] or "").strip()
    try:
        if firebase_uid:
            firebase_user = auth.get_user(firebase_uid)
        else:
            email = _firebase_email(user)
            try:
                firebase_user = auth.get_user_by_email(email)
            except auth.UserNotFoundError:
                if not allow_create:
                    raise FirebaseAuthProvisioningError(
                        "This account is not linked to Firebase Authentication. Contact the administrator."
                    )
                firebase_user = auth.create_user(
                    email=email,
                    password=new_password,
                    display_name=user["full_name"],
                    disabled=False,
                )

        claims = dict(firebase_user.custom_claims or {})
        existing_school_user_id = str(claims.get("school_user_id", "")).strip()
        if existing_school_user_id and existing_school_user_id != str(user["id"]):
            raise FirebaseAuthProvisioningError("This Firebase account is already linked to another school record.")
        if claims.get("admin") is True:
            raise FirebaseAuthProvisioningError("A school account cannot be linked to an administrator Firebase account.")

        # A newly created account already has this password, but update_user keeps the operation
        # identical for new and existing accounts and ensures reset never touches Flask hashes.
        firebase_user = auth.update_user(
            firebase_user.uid,
            password=new_password,
            display_name=user["full_name"],
        )
        claims.update({"role": user["role"], "school_user_id": str(user["id"])})
        auth.set_custom_user_claims(firebase_user.uid, claims)
        return firebase_user.uid
    except FirebaseAuthProvisioningError:
        raise
    except Exception as error:
        raise FirebaseAuthProvisioningError(
            "Unable to update Firebase Authentication. Please try again later."
        ) from error


def create_pending_email_account(email: str, password: str, display_name: str) -> tuple[str, bool]:
    """Create an unlinked Firebase email/password user for a registration in progress.

    Sending the Firebase verification email is intentionally done by the signed-in Web/Android
    Firebase SDK.  The Admin SDK never exposes a service account or password-reset capability to
    a browser or device.
    """
    auth = _firebase_auth()
    email = email.strip().lower()
    try:
        try:
            auth.get_user_by_email(email)
        except auth.UserNotFoundError:
            firebase_user = auth.create_user(
                email=email,
                password=password,
                display_name=display_name,
                email_verified=False,
                disabled=False,
            )
            auth.set_custom_user_claims(firebase_user.uid, {"registration_pending": True})
            return firebase_user.uid, True
        # Firebase permits one password identity per email.  The caller must prove control of
        # this existing identity with a current ID token before linking another school profile.
        return firebase_user.uid, False
    except FirebaseAuthProvisioningError:
        raise
    except Exception as error:
        raise FirebaseAuthProvisioningError(
            "Unable to create the Firebase account. Please try again later."
        ) from error


def verify_pending_email(firebase_uid: str, firebase_id_token: str) -> str:
    """Return the verified email only when the token and Firebase user agree."""
    auth = _firebase_auth()
    try:
        claims = auth.verify_id_token(firebase_id_token, check_revoked=True)
        if claims.get("uid") != firebase_uid:
            raise FirebaseAuthProvisioningError("The Firebase verification session does not match this registration.")
        firebase_user = auth.get_user(firebase_uid)
        if not firebase_user.email_verified:
            raise FirebaseAuthProvisioningError("Please verify your email before continuing.")
        if not firebase_user.email:
            raise FirebaseAuthProvisioningError("The Firebase account has no email address.")
        return firebase_user.email.strip().lower()
    except FirebaseAuthProvisioningError:
        raise
    except Exception as error:
        raise FirebaseAuthProvisioningError(
            "Unable to confirm Firebase email verification. Please try again later."
        ) from error


def firebase_identity_email(firebase_uid: str, firebase_id_token: str) -> tuple[str, bool]:
    """Validate ownership of an existing Firebase email identity, whether or not it is verified."""
    auth = _firebase_auth()
    try:
        claims = auth.verify_id_token(firebase_id_token, check_revoked=True)
        if claims.get("uid") != firebase_uid:
            raise FirebaseAuthProvisioningError("The Firebase sign-in session does not match this email.")
        firebase_user = auth.get_user(firebase_uid)
        if not firebase_user.email:
            raise FirebaseAuthProvisioningError("The Firebase account has no email address.")
        return firebase_user.email.strip().lower(), bool(firebase_user.email_verified)
    except FirebaseAuthProvisioningError:
        raise
    except Exception as error:
        raise FirebaseAuthProvisioningError("Unable to confirm the Firebase email identity.") from error


def finalize_email_account(firebase_uid: str, role: str, school_user_id: int) -> None:
    """Mark a verified Firebase identity as school-linked without encoding one profile in claims."""
    auth = _firebase_auth()
    try:
        firebase_user = auth.get_user(firebase_uid)
        claims = dict(firebase_user.custom_claims or {})
        if claims.get("admin") is True:
            raise FirebaseAuthProvisioningError("An administrator Firebase account cannot be linked to a public school profile.")
        claims.pop("registration_pending", None)
        # Roles/profile IDs are intentionally selected and authorized on Flask for every
        # session.  Firebase custom claims cannot safely express a variable number of profiles.
        claims.pop("role", None)
        claims.pop("school_user_id", None)
        claims["school_multi_profile"] = True
        auth.set_custom_user_claims(firebase_uid, claims)
    except FirebaseAuthProvisioningError:
        raise
    except Exception as error:
        raise FirebaseAuthProvisioningError(
            "Unable to finalize the Firebase account. Please try again later."
        ) from error


def verified_firebase_uid(firebase_id_token: str) -> str:
    """Validate a normal Firebase sign-in token and enforce verified real-email accounts."""
    auth = _firebase_auth()
    try:
        claims = auth.verify_id_token(firebase_id_token, check_revoked=True)
        uid = str(claims.get("uid", "")).strip()
        if not uid:
            raise FirebaseAuthProvisioningError("Invalid Firebase sign-in session.")
        firebase_user = auth.get_user(uid)
        if firebase_user.email and not firebase_user.email.endswith("@cns-paunta.app") and not firebase_user.email_verified:
            raise FirebaseAuthProvisioningError("Please verify your email before logging in.")
        return uid
    except FirebaseAuthProvisioningError:
        raise
    except Exception as error:
        raise FirebaseAuthProvisioningError("Unable to verify Firebase sign-in. Please try again later.") from error


def verified_firebase_admin_uid(firebase_id_token: str) -> str:
    """Validate a Firebase token that has the server-assigned administrator claim."""
    auth = _firebase_auth()
    try:
        claims = auth.verify_id_token(firebase_id_token, check_revoked=True)
        uid = str(claims.get("uid", "")).strip()
        if not uid or claims.get("admin") is not True:
            raise FirebaseAuthProvisioningError("Administrator authorization is required.")
        return uid
    except FirebaseAuthProvisioningError:
        raise
    except Exception as error:
        raise FirebaseAuthProvisioningError("Unable to verify administrator authorization. Please try again later.") from error
