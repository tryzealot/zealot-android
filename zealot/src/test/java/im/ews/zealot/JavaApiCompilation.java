package im.ews.zealot;

import android.app.Activity;
import okhttp3.Call;
import okhttp3.OkHttpClient;

import java.util.concurrent.Executor;

/** Compiled with the unit tests to guard the public Java integration surface. */
final class JavaApiCompilation {
    static Call configureAndCheck(Activity activity, OkHttpClient client, Executor executor) {
        Zealot zealot = Zealot.create(activity)
            .setEndpoint("https://zealot.example.com")
            .setChannelKey("channel", Zealot.BUILD_TYPE)
            .setBuildType(Zealot.BUILD_TYPE)
            .setAlertMaxHeight(Zealot.ScreenHeight.HALFSCREEN)
            .setHttpClient(client)
            .setCallbackExecutor(executor)
            .setUpdatePresenter((screen, release) -> {
                // An application can present release details in its own UI.
            });
        return zealot.checkForUpdate(new UpdateCallback() {
            @Override public void onUpdateAvailable(ReleaseInfo release) {}
            @Override public void onUpToDate() {}
            @Override public void onError(UpdateError error) {}
        });
    }
}
