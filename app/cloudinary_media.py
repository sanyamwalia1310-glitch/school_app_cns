"""Server-only Cloudinary media service.

The Android client uploads files to Flask.  Flask alone configures Cloudinary and
returns only delivery metadata, never its API secret.
"""

from __future__ import annotations

import time
from dataclasses import dataclass

from flask import current_app
from werkzeug.datastructures import FileStorage
from werkzeug.utils import secure_filename


class CloudinaryUnavailable(RuntimeError):
    """Raised when Cloudinary was intentionally not configured on this server."""


@dataclass(frozen=True)
class UploadedMedia:
    public_id: str
    secure_url: str
    resource_type: str
    delivery_type: str
    format: str
    original_filename: str
    bytes: int


def _cloudinary_modules():
    cloud_name = current_app.config["CLOUDINARY_CLOUD_NAME"]
    api_key = current_app.config["CLOUDINARY_API_KEY"]
    api_secret = current_app.config["CLOUDINARY_API_SECRET"]
    if not (cloud_name and api_key and api_secret):
        raise CloudinaryUnavailable("Cloudinary is not configured on this server.")

    import cloudinary
    import cloudinary.uploader
    import cloudinary.utils

    cloudinary.config(cloud_name=cloud_name, api_key=api_key, api_secret=api_secret, secure=True)
    return cloudinary.uploader, cloudinary.utils


def _upload(file_storage: FileStorage, *, folder: str, delivery_type: str) -> UploadedMedia:
    if not file_storage or not file_storage.filename:
        raise ValueError("Choose a file to upload.")
    original_filename = secure_filename(file_storage.filename)
    if not original_filename:
        raise ValueError("The file name is invalid.")

    uploader, _ = _cloudinary_modules()
    result = uploader.upload(
        file_storage.stream,
        resource_type="auto",
        type=delivery_type,
        folder=folder,
        use_filename=False,
        unique_filename=True,
        overwrite=False,
        filename_override=original_filename,
    )
    url = str(result.get("secure_url", "")).strip()
    public_id = str(result.get("public_id", "")).strip()
    if not (url.startswith("https://") and public_id):
        raise RuntimeError("Cloudinary did not return a secure delivery URL.")
    return UploadedMedia(
        public_id=public_id,
        secure_url=url,
        resource_type=str(result.get("resource_type", "raw")),
        delivery_type=delivery_type,
        format=str(result.get("format", "")),
        original_filename=original_filename,
        bytes=int(result.get("bytes", 0) or 0),
    )


def upload_public(file_storage: FileStorage, *, folder: str) -> UploadedMedia:
    """Upload public school content such as gallery and event images."""
    return _upload(file_storage, folder=folder, delivery_type="upload")


def upload_private(file_storage: FileStorage, *, folder: str) -> UploadedMedia:
    """Upload homework/test files as authenticated Cloudinary assets."""
    return _upload(file_storage, folder=folder, delivery_type="authenticated")


def delete_media(*, public_id: str, resource_type: str, delivery_type: str) -> None:
    """Best-effort deletion after the database has stopped referencing an asset."""
    uploader, _ = _cloudinary_modules()
    uploader.destroy(
        public_id,
        resource_type=resource_type or "raw",
        type=delivery_type or "upload",
        invalidate=True,
    )


def private_download_url(*, public_id: str, resource_type: str, file_format: str) -> str:
    """Create a short-lived URL only after Flask authorizes the requesting profile."""
    _, utils = _cloudinary_modules()
    return utils.private_download_url(
        public_id,
        file_format or None,
        resource_type=resource_type or "raw",
        type="authenticated",
        expires_at=int(time.time()) + 5 * 60,
        attachment=True,
    )
