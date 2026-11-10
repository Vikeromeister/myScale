#!/bin/bash
set -euo pipefail

GDRIVE_FILE_ID="1POGadi4bJWSa35dsQROjBejierI-bDmb"
APK_PATH="${1:-/home/antigravity/Vikeromeister/myScale/android_app/app/build/outputs/apk/debug/myScale-debug.apk}"

if [ ! -f "$APK_PATH" ]; then
    echo "Error: APK file not found at $APK_PATH" >&2
    exit 1
fi

echo "Uploading / updating APK on Google Drive (File ID: $GDRIVE_FILE_ID)..."
export ANTIGRAVITY_APP_DATA_DIR=/tmp

# Try updating the existing file first (preserves link and file ID)
if gdrive mutate update "$GDRIVE_FILE_ID" "$APK_PATH" --mime-type "application/vnd.android.package-archive" 2>/dev/null; then
    echo "Successfully updated existing file: https://drive.google.com/file/d/$GDRIVE_FILE_ID/view?usp=drivesdk"
else
    echo "File ID not accessible or not found; uploading as fresh file..."
    gdrive mutate upload "$APK_PATH" --mime-type "application/vnd.android.package-archive"
fi
