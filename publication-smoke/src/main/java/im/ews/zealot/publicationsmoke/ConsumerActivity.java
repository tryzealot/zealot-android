package im.ews.zealot.publicationsmoke;

import android.app.Activity;
import android.os.Bundle;
import im.ews.zealot.ReleaseInfo;
import im.ews.zealot.UpdateCallback;
import im.ews.zealot.UpdateError;
import im.ews.zealot.UpdateResult;
import im.ews.zealot.Zealot;
import okhttp3.Call;

/** Compiles and survives R8 using only the published AAR and its transitive dependencies. */
public final class ConsumerActivity extends Activity {
    private Call call;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        Zealot zealot = Zealot.create(this)
            .setEndpoint("https://zealot.example.com")
            .setChannelKey("channel")
            .setBuildType(Zealot.BUILD_TYPE);

        // Keep both the original Java callback and the typed overload in the release APK.
        call = zealot.checkForUpdate(new UpdateCallback() {
            @Override public void onUpdateAvailable(ReleaseInfo release) {
                release.getInstallUrl();
            }
            @Override public void onUpToDate() {}
            @Override public void onError(UpdateError error) {
                error.getCode();
            }
        });
        call.cancel();

        call = zealot.checkForUpdate(result -> {
            if (result instanceof UpdateResult.UpdateAvailable) {
                ((UpdateResult.UpdateAvailable) result).getRelease().getInstallUrl();
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (call != null) call.cancel();
        super.onDestroy();
    }
}
