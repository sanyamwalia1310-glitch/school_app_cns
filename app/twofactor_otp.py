"""Server-side 2Factor OTP helpers."""

from __future__ import annotations

from urllib.parse import quote

import requests
from flask import current_app


class TwoFactorOtpError(ValueError):
    """Raised for an invalid phone number or failed 2Factor OTP action."""


def normalize_indian_phone(raw_phone: str) -> str:
    """Return a valid Indian number in E.164 form."""
    digits = "".join(character for character in (raw_phone or "") if character.isdigit())
    if len(digits) == 10:
        return f"+91{digits}"
    if len(digits) == 12 and digits.startswith("91"):
        return f"+{digits}"
    raise TwoFactorOtpError("Enter a valid Indian 10-digit mobile number.")


def _api_key() -> str:
    api_key = current_app.config.get("TWOFACTOR_API_KEY", "").strip()
    if not api_key:
        raise TwoFactorOtpError("SMS OTP is not configured. Set TWOFACTOR_API_KEY on the server.")
    return api_key


def _twofactor_request(path: str) -> dict:
    """Call the existing 2Factor SMS verification endpoint server-side."""
    try:
        # The 2Factor V1 OTP examples use POST.  The recipient part of that
        # legacy URL is an Indian 10-digit mobile number, while this app stores
        # the same number safely in E.164 form (+91XXXXXXXXXX).
        response = requests.post(f"https://2factor.in/API/V1/{path}", timeout=15)
        payload = response.json()
    except (requests.RequestException, ValueError) as error:
        raise TwoFactorOtpError("Unable to contact the SMS provider. Please try again.") from error

    if not response.ok or payload.get("Status") != "Success":
        detail = str(payload.get("Details", "")).casefold()
        current_app.logger.warning(
            "2Factor rejected an OTP request (HTTP %s): %s",
            response.status_code,
            payload.get("Details", "no provider detail"),
        )
        if "session" in detail:
            raise TwoFactorOtpError("The OTP is invalid or has expired. Send a new OTP.")
        if "api key" in detail or "invalid api" in detail or "unauthorized" in detail:
            raise TwoFactorOtpError(
                "SMS OTP is not enabled for this server. Contact the school administrator."
            )
        if "balance" in detail or "credit" in detail:
            raise TwoFactorOtpError(
                "The SMS OTP service has no available credits. Contact the school administrator."
            )
        if "mobile" in detail or "number" in detail:
            raise TwoFactorOtpError("The SMS provider rejected this mobile number.")
        raise TwoFactorOtpError("The SMS OTP service rejected this request. Please try again later.")
    return payload


def _twofactor_sms_send(phone: str) -> dict:
    """Send a 2Factor SMS OTP without any Voice/OBD/Say/Ask endpoint."""
    provider_mobile = phone[-10:]
    payload = _twofactor_request(
        f"{quote(_api_key(), safe='')}/SMS/{quote(provider_mobile, safe='')}/AUTOGEN"
    )
    # Log only non-secret metadata. Never log the API key, phone number, OTP,
    # or provider session ID.
    current_app.logger.info(
        "2Factor SMS OTP request accepted (response keys=%s)",
        sorted(str(key) for key in payload.keys()),
    )
    return payload


def send_otp(phone: str) -> str:
    """Ask 2Factor to generate and send an OTP; return its verification session ID."""
    normalized_phone = normalize_indian_phone(phone)
    payload = _twofactor_sms_send(normalized_phone)
    session_id = str(payload.get("Details", "")).strip()
    if not session_id:
        raise TwoFactorOtpError("The SMS provider did not return a verification session. Try again.")
    return session_id


def verify_otp(session_id: str, otp: str) -> bool:
    """Verify with 2Factor without ever persisting the OTP locally."""
    if not session_id or not otp or not otp.isdigit():
        raise TwoFactorOtpError("Enter the OTP sent to your mobile number.")
    _twofactor_request(
        f"{quote(_api_key(), safe='')}/SMS/VERIFY/{quote(session_id, safe='')}/{quote(otp, safe='')}"
    )
    return True
