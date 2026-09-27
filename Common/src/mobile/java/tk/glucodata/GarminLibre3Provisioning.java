/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2 and 3 sensors.                         */

package tk.glucodata;

import java.util.ArrayList;
import java.util.List;

/** Builds the one-time provisioning record for the direct-BLE Libre 3 Garmin app. */
public final class GarminLibre3Provisioning {
    private static final int SECRET_SIZE = 180; // PIN[4] + challenge context[176]
    private static final int RECORD_SIZE = 191;

    private GarminLibre3Provisioning() {}

    /**
     * Connect IQ 3.1 supports List/Number directly.  Keep the provisioning data
     * binary in Juggluco and expose each unsigned byte as one Integer; no Base64.
     */
    public static List<Integer> makeMessage(String bluetoothAddress, byte[] secret180) {
        if (bluetoothAddress == null)
            throw new IllegalArgumentException("Bluetooth address is null");
        if (secret180 == null || secret180.length != SECRET_SIZE)
            throw new IllegalArgumentException("Libre 3 Garmin secret must be 180 bytes");

        final String[] fields = bluetoothAddress.split(":");
        if (fields.length != 6)
            throw new IllegalArgumentException("Invalid Bluetooth address");

        final byte[] record = new byte[RECORD_SIZE];
        record[0] = 'L';
        record[1] = '3';
        record[2] = 'G';
        record[3] = '2';
        record[4] = 1;

        for (int i = 0; i < 6; ++i) {
            if (fields[i].length() != 2)
                throw new IllegalArgumentException("Invalid Bluetooth address");
            final int value;
            try {
                value = Integer.parseInt(fields[i], 16);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Invalid Bluetooth address", ex);
            }
            record[5 + i] = (byte)value;
        }

        System.arraycopy(secret180, 0, record, 11, SECRET_SIZE);

        final ArrayList<Integer> message = new ArrayList<>(RECORD_SIZE);
        for (byte value : record)
            message.add(value & 0xff);
        return message;
    }
}
