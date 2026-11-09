#!/usr/bin/env bash
# Uninstall the amazon_music_connector_host native messaging host for Firefox on Linux/macOS.

set -euo pipefail

if [[ "$OSTYPE" == darwin* ]]; then
  HOSTS_DIRS=("$HOME/Library/Application Support/Mozilla/NativeMessagingHosts")
  CONFIG_DIR="~/Library/Application Support/amazon-music-connector-for-hitster"
else
  HOSTS_DIRS=("$HOME/.mozilla/native-messaging-hosts"
              "$HOME/snap/firefox/common/.mozilla/native-messaging-hosts"
              "$HOME/.var/app/org.mozilla.firefox/.mozilla/native-messaging-hosts")
  CONFIG_DIR="~/.config/amazon-music-connector-for-hitster"
fi

echo
echo "This will uninstall the helper app of the Firefox add-on"
echo "\"Amazon Music Connector for Hitster\"."
echo
echo "The configuration (port and token) and the copy of the Android app stay in:"
echo "      $CONFIG_DIR"
echo

read -p "Proceed with uninstallation? [y/n] " -n 1 -r
echo
echo
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
  echo "Uninstallation cancelled."
  read -n1 -s -r -p "Press any key to exit..."
  echo
  echo
  exit 1
fi

for HOSTS_DIR in "${HOSTS_DIRS[@]}"; do
  MANIFEST_DEST="$HOSTS_DIR/amazon_music_connector_host.json"
  INSTALL_DIR="$HOSTS_DIR/amazon_music_connector_host_helper"
  if [[ -f "$MANIFEST_DEST" ]]; then
    rm "$MANIFEST_DEST"
    echo "Registration file removed: $MANIFEST_DEST"
  fi
  if [[ -d "$INSTALL_DIR" ]]; then
    rm -r "$INSTALL_DIR"
    echo "Helper app removed:        $INSTALL_DIR"
  fi
done
echo
echo "Restart Firefox to apply the changes."
echo
read -n1 -s -r -p "Press any key to exit..."
echo
echo
