package im.ews.zealot.publicationsmoke;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import im.ews.zealot.ReleaseInfo;
import im.ews.zealot.UpdateCallback;
import im.ews.zealot.UpdateError;
import im.ews.zealot.UpdateResult;
import im.ews.zealot.Zealot;
import okhttp3.Call;

/** Compiles and survives R8 using only the published AAR and its transitive dependencies. */
public final class ConsumerActivity extends Activity {
    public static final String EXTRA_ENDPOINT = "zealot.endpoint";
    public static final String EXTRA_CHANNEL_KEY = "zealot.channel_key";

    private Call call;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        TextView status = new TextView(this);
        status.setText("Checking updates");
        setContentView(status);

        String endpoint = getIntent().getStringExtra(EXTRA_ENDPOINT);
        String channelKey = getIntent().getStringExtra(EXTRA_CHANNEL_KEY);

        Zealot zealot = Zealot.create(this)
            .setEndpoint(endpoint == null ? "https://zealot.example.com" : endpoint)
            .setChannelKey(channelKey == null ? "channel" : channelKey)
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
                ReleaseInfo release = ((UpdateResult.UpdateAvailable) result).getRelease();
                status.setText("Update available: " + release.getReleaseVersion());
            } else if (result instanceof UpdateResult.UpToDate) {
                status.setText("Up to date");
            } else if (result instanceof UpdateResult.Error) {
                status.setText("Error: " + ((UpdateResult.Error) result).getError().getCode());
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (call != null) call.cancel();
        super.onDestroy();
    }
}
