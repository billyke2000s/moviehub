package local.tv.home;

import android.os.Bundle;
import androidx.test.runner.AndroidJUnitRunner;

/**
 * Runs the device tests with the first-start messages already answered, the TV layout chosen and
 * the box treated as online, so none of them covers the screens under test. SafetyNoticeTest,
 * FirstStartTest and PhoneModeTest show them on purpose.
 */
public class MovieHubTestRunner extends AndroidJUnitRunner {

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        getTargetContext()
            .getSharedPreferences("ui", 0)
            .edit()
            .putBoolean(SafetyNotice.PREF, true)
            .putBoolean(ProviderSettings.ASKED, true)
            .putBoolean(FirstStart.TMDB_ASKED, true)
            .putString(DeviceMode.PREF, DeviceMode.TV)
            .commit();
        // An emulator without a network must not cover screens with "Connect to the internet".
        ConnectionPrompt.testOnline = true;
    }
}
