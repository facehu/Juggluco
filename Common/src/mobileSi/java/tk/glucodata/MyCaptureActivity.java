package tk.glucodata;

import com.google.zxing.client.android.Intents;
import com.journeyapps.barcodescanner.BarcodeView;
import com.journeyapps.barcodescanner.CameraPreview;
import com.journeyapps.barcodescanner.CaptureActivity;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import com.journeyapps.barcodescanner.camera.CameraInstance;
import com.journeyapps.barcodescanner.camera.FitCenterStrategy;

/** Register with @style/zxing_CaptureTheme and select via IntentIntegrator. */
public class MyCaptureActivity extends CaptureActivity {
    @Override
    protected DecoratedBarcodeView initializeContent() {
        // CaptureActivity reads the intent after this method returns.
        getIntent().putExtra(Intents.Scan.SCAN_TYPE, Intents.Scan.MIXED_SCAN);

        final DecoratedBarcodeView decorated = new DecoratedBarcodeView(this);
        final BarcodeView preview = decorated.getBarcodeView();
        preview.setUseTextureView(false);
        preview.setPreviewScalingStrategy(new FitCenterStrategy());

        preview.addStateListener(new CameraPreview.StateListener() {
            private boolean parametersQueued;

            @Override
            public void previewSized() {
                if (parametersQueued) {
                    return;
                }
                final CameraInstance instance = preview.getCameraInstance();
                if (instance == null || !instance.isOpen()) {
                    return;
                }
                parametersQueued = true;

                // JourneyApps 4.3.0: queue after configuration, before preview
                // startup. The parameter lambda executes on the camera thread.
                preview.changeCameraParameters(parameters -> {
                    parameters.setRecordingHint(true);
                    return parameters;
                });
            }

            @Override
            public void previewStarted() {
            }

            @Override
            public void previewStopped() {
                parametersQueued = false;
            }

            @Override
            public void cameraError(Exception error) {
                // CaptureManager already displays the camera-error dialog.
            }

            @Override
            public void cameraClosed() {
                parametersQueued = false;
            }
        });

        setContentView(decorated);
        return decorated;
    }
}
