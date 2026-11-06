package net.ossrs.yasea.demo.studio;

import android.app.admin.DeviceAdminReceiver;

/**
 * Lets the app become the device owner (optional, for a true locked-down kiosk):
 * adb shell dpm set-device-owner net.ossrs.yasea.demo/.studio.AdminReceiver
 * Without it kiosk mode still works through Android's screen pinning.
 */
public class AdminReceiver extends DeviceAdminReceiver {
}
