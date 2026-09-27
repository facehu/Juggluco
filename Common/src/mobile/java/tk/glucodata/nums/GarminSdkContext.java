/* Guard Kerfstok broadcasts before Garmin's SDK attempts deserialization. */
package tk.glucodata.nums;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.SystemClock;

import com.garmin.android.connectiq.ConnectIQ;
import com.garmin.android.connectiq.IQApp;
import com.garmin.android.connectiq.IQDevice;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;

import tk.glucodata.Log;

/** The SDK deserializes INCOMING_MESSAGE even after its app listener is removed.
 * Keep the guard on the registered receiver, not in the already-decoded callback.
 * Other SDK events continue through the original receiver. */
final class GarminSdkContext extends ContextWrapper {
    private static final String LOG_ID = "GarminSdkContext";
    private final AllData owner;
    private final IdentityHashMap<BroadcastReceiver,BroadcastReceiver> receivers = new IdentityHashMap<>();
    private volatile boolean closed;
    // Summary for a missing application reply. Count at the raw broadcast
    // boundary, including early returns, rather than assuming that a completed
    // Bluetooth transfer necessarily reached Juggluco. No per-reading logging.
    private long incomingCount, decodedCount, dispatchedCount;
    private long lastIncomingAt, lastIncomingPeer=Long.MIN_VALUE;
    private String incomingStage="none";

    GarminSdkContext(Context context, AllData owner) {
        super(context.getApplicationContext());
        this.owner=owner;
    }

    @Override public Context getApplicationContext() { return this; }

    private BroadcastReceiver wrap(BroadcastReceiver receiver, IntentFilter filter) {
        if (receiver==null || filter==null || !filter.hasAction(ConnectIQ.INCOMING_MESSAGE)) return receiver;
        BroadcastReceiver existing=receivers.get(receiver);
        if (existing!=null) return existing;
        BroadcastReceiver guarded=new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (closed || intent==null) return;
                final String action=intent.getAction();
                if (ConnectIQ.INCOMING_MESSAGE.equals(action)) {
                    receiveIncoming(intent);
                    return;
                }
                receiver.onReceive(context,intent);
            }
        };
        receivers.put(receiver,guarded);
        return guarded;
    }

    private void receiveIncoming(Intent intent) {
        long peerId=Long.MIN_VALUE;
        ++incomingCount;
        lastIncomingAt=SystemClock.elapsedRealtime();
        lastIncomingPeer=Long.MIN_VALUE;
        incomingStage="raw broadcast";
        try {
            final IQDevice device=intent.getParcelableExtra(ConnectIQ.EXTRA_REMOTE_DEVICE);
            if (device==null) { incomingStage="missing device"; return; }
            peerId=device.getDeviceIdentifier();
            lastIncomingPeer=peerId;
            // Validate the application before using this broadcast as health
            // evidence. Another Connect IQ app on the same watch must never make
            // Juggluco abandon a working Direct Kerfstok connection.
            final IQApp app=intent.getParcelableExtra(ConnectIQ.EXTRA_REMOTE_APPLICATION);
            if (app==null || app.getApplicationId()==null) { incomingStage="missing app"; return; }
            if (!owner.acceptsGarminApplication(peerId,app.getApplicationId())) {
                incomingStage="unaccepted app";
                return;
            }
            // In Automatic mode, a real Kerfstok packet arriving through Garmin
            // Connect proves substantially more than IQDeviceStatus.CONNECTED: the
            // Connect IQ application channel itself is alive. If Direct currently
            // owns this watch, AllData verifies command ownership before using
            // a reply. Libre3 records may be decoded without a handoff, so a
            // transfer accepted by GCM during Direct reconnection can be saved
            // and acknowledged through the current owner.
            if (!owner.prepareGarminConnectIncoming(device)) {
                incomingStage="ownership transition";
                if (Log.doLog) Log.i(LOG_ID,"ignore Garmin Connect Kerfstok payload during ownership transition peer="+peerId);
                return;
            }
            // Decide ownership before accessing, allocating, or decoding the payload.
            if (!owner.acceptsGarminConnectIncoming(peerId)) {
                incomingStage="ownership rejected";
                if (Log.doLog) Log.i(LOG_ID,"ignore Garmin Connect payload before decode peer="+peerId);
                return;
            }
            incomingStage="payload access";
            final byte[] payload=intent.getByteArrayExtra(ConnectIQ.EXTRA_PAYLOAD);
            // Kerfstok sends one root object. Use the same bounded codec as Direct
            // BLE; Garmin's receiver would decode even with a null app listener,
            // and lets BufferUnderflowException escape its broadcast callback.
            incomingStage="payload decode";
            final Object decoded=GarminIQCodec.decode(payload);
            ++decodedCount;
            incomingStage="application dispatch";
            owner.onGarminConnectMessage(device,app,decoded);
            ++dispatchedCount;
            incomingStage="dispatched";
        } catch (IOException | RuntimeException error) {
            incomingStage=incomingStage+": "+error.getClass().getSimpleName();
            Log.stack(LOG_ID,"invalid Garmin Connect message peer="+peerId,error);
            // Existing application-reply watchdogs handle a missing response.
        }
    }

    // Global to this SDK registration, not a per-watch success counter.
    // "Dispatched" means passed to AllData; it does not mean a glucose save.
    String incomingDiagnostics() {
        return "Garmin SDK receive since init: broadcasts="+incomingCount+
                " decoded="+decodedCount+" dispatched="+dispatchedCount+
                " lastPeer="+lastIncomingPeer+" stage="+incomingStage+
                " ageMs="+(incomingCount==0?-1L:SystemClock.elapsedRealtime()-lastIncomingAt);
    }

    @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        return getBaseContext().registerReceiver(wrap(receiver,filter),filter);
    }

    @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
                                              String permission, Handler scheduler) {
        return getBaseContext().registerReceiver(wrap(receiver,filter),filter,permission,scheduler);
    }

    @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, int flags) {
        return getBaseContext().registerReceiver(wrap(receiver,filter),filter,flags);
    }

    @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
                                              String permission, Handler scheduler, int flags) {
        return getBaseContext().registerReceiver(wrap(receiver,filter),filter,permission,scheduler,flags);
    }

    @Override public void unregisterReceiver(BroadcastReceiver receiver) {
        final BroadcastReceiver guarded=receivers.remove(receiver);
        getBaseContext().unregisterReceiver(guarded==null?receiver:guarded);
    }

    void close() {
        closed=true;
        // Also clean up if SDK shutdown failed before unregisterReceiver().
        for (BroadcastReceiver receiver:new ArrayList<>(receivers.keySet())) {
            try { unregisterReceiver(receiver); }
            catch (RuntimeException error) { Log.stack(LOG_ID,"unregister SDK receiver",error); }
        }
    }
}
