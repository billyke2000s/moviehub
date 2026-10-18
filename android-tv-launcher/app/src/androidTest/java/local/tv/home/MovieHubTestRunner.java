package local.tv.home;

import android.os.Bundle;
import androidx.test.runner.AndroidJUnitRunner;

/**
 * Runs the device tests with the first-start safety notice already read, so it does not cover
 * the screens under test. SafetyNoticeTest shows it on purpose.
 */
public class MovieHubTestRunner extends AndroidJUnitRunner {

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        getTargetContext().getSharedPreferences("ui", 0).edit().putBoolean(SafetyNotice.PREF, true).commit();
    }
}
