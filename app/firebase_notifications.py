"""Server-side Firebase Cloud Messaging delivery.

Android never chooses private-notification recipients.  A device token is registered
against one authorized school profile and this module resolves that profile again in
the server database before sending.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Mapping

from .firebase_auth import _firebase_auth


@dataclass(frozen=True)
class DeliveryResult:
    attempted: int
    delivered: int


def _messaging():
    _firebase_auth()  # Ensures Firebase Admin is configured with server-only credentials.
    from firebase_admin import messaging

    return messaging


def _string_data(data: Mapping[str, object] | None) -> dict[str, str]:
    return {str(key): str(value) for key, value in (data or {}).items() if value is not None}


def send_public_notification(*, title: str, body: str, data: Mapping[str, object] | None = None) -> DeliveryResult:
    """Broadcast only non-sensitive school-wide notices to the public topic."""
    messaging = _messaging()
    payload = {"delivery_scope": "public", **_string_data(data)}
    messaging.send(
        messaging.Message(
            topic="school_public",
            notification=messaging.Notification(title=title, body=body),
            data=payload,
        )
    )
    return DeliveryResult(attempted=1, delivered=1)


def send_profile_notification(
    db,
    *,
    profile_user_id: int,
    title: str,
    body: str,
    data: Mapping[str, object] | None = None,
) -> DeliveryResult:
    """Deliver a private message to devices registered for exactly one school profile."""
    messaging = _messaging()
    rows = db.execute(
        "SELECT token FROM fcm_device_tokens WHERE user_id = ? ORDER BY updated_at DESC",
        (profile_user_id,),
    ).fetchall()
    payload = {
        "delivery_scope": "profile",
        "target_profile_id": str(profile_user_id),
        **_string_data(data),
    }
    delivered = 0
    stale_tokens: list[str] = []
    for row in rows:
        token = row["token"]
        try:
            messaging.send(
                messaging.Message(
                    token=token,
                    notification=messaging.Notification(title=title, body=body),
                    data=payload,
                )
            )
            delivered += 1
        except Exception as error:  # Token lifecycle errors must never break marks persistence.
            if error.__class__.__name__ in {"UnregisteredError", "SenderIdMismatchError"}:
                stale_tokens.append(token)
    if stale_tokens:
        db.executemany("DELETE FROM fcm_device_tokens WHERE token = ?", ((token,) for token in stale_tokens))
    return DeliveryResult(attempted=len(rows), delivered=delivered)
