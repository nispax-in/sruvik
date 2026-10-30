# Release and update plan

Sruvik is designed to be distributed outside Google Play as a signed APK attached to GitHub Releases.

The update feature is not enabled yet. Before implementation, define a stable release metadata format, verify HTTPS responses, compare version codes, validate APK signing certificates, and hand installation to Android's package installer. Do not silently install packages or request package-install permission at app startup.
