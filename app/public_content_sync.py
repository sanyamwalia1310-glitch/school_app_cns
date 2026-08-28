"""Server-authoritative Firestore snapshots for public school content only."""

from __future__ import annotations

import json
import time

from .firebase_auth import _firebase_auth


def sync_public_gallery(db) -> None:
    """Publish Cloudinary gallery URLs without copying any private data to Firestore."""
    rows = db.execute(
        """SELECT id, title, caption, image_url, sort_order
        FROM gallery_items ORDER BY sort_order, id"""
    ).fetchall()
    gallery = [
        {
            "id": row["id"],
            "title": row["title"],
            "subtitle": row["caption"],
            "imageUrl": row["image_url"],
            "imageResName": "",
        }
        for row in rows
    ]
    _firebase_auth()
    from firebase_admin import firestore

    firestore.client().collection("public_content").document("schoolhub").set(
        {
            "schemaVersion": 1,
            "updatedAt": int(time.time() * 1000),
            "gallery": json.dumps(gallery),
        },
        merge=True,
    )
