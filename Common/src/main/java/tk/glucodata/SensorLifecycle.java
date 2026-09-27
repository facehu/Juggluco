package tk.glucodata;

import android.content.Context;
import android.content.SharedPreferences;

/** Sensor lifecycle notifications shared by the phone and Wear builds. The
 * native active-sensor list owns end/use-again state, including across restart.
 * Remember only the last explicit addition as a candidate preference; a Java
 * notification or an old preference must never override native activity. */
public final class SensorLifecycle {
    public interface Listener {
        void changed();
        /** A UI finish action must update local settings before it returns. */
        void ended();
    }
    private static volatile Listener listener;
    private SensorLifecycle() {}

    private static SharedPreferences prefs() {
        return Applic.app.getSharedPreferences("sensor_lifecycle", Context.MODE_PRIVATE);
    }

    public static void listen(Listener callback) {
        listener=callback;
        changed();
    }

    public static void changed() {
        Listener callback=listener;
        if(callback!=null) callback.changed();
    }

    public static void ended(String serial) {
        if(serial==null) return;
        // Remove the obsolete v40/v41 marker. Native finish happens before
        // this notification; a later native reactivation must remain usable.
        prefs().edit().remove("ended:"+serial).apply();
        Listener callback=listener;
        if(callback!=null) callback.ended();
    }

    public static void added(String serial) {
        if(serial==null) return;
        prefs().edit().remove("ended:"+serial).putString("added",serial).apply();
        changed();
    }

    /** Sensors.useAgain passes SensorGlucoseData*, not the owning dataptr. */
    public static void added(long sensorptr) {
        String[] active=Natives.activeSensors();
        if(active!=null) for(String serial:active) {
            if(serial==null) continue;
            long ptr=Natives.getdataptr(serial);
            if(ptr==0L) continue;
            try {
                if(Natives.getsensorptr(ptr)==sensorptr) {
                    added(serial);
                    return;
                }
            } finally { Natives.freedataptr(ptr); }
        }
        changed();
    }

    public static String lastAdded() { return prefs().getString("added",null); }
}
