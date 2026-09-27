/*
 * Minimal legacy Garmin Connect IQ object codec and XXTEA transport codec.
 * Derived from observed Garmin Connect 5.28.1 / vivoactive 3 traffic.
 */
package tk.glucodata.nums;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

final class GarminIQCodec {
    private GarminIQCodec() {}

    private static final int STRING_MAGIC = 0xABCDABCD;
    private static final int DATA_MAGIC   = 0xDA7ADA7A;
    static final int MAX_MESSAGE_BYTES = 1024 * 1024;
    private static final int MAX_NESTING = 64;

    static byte[] encode(Object root) throws IOException {
        final LinkedHashMap<String,Integer> strings = new LinkedHashMap<>();
        collectStrings(root, strings);

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final DataOutputStream dos = new DataOutputStream(out);

        if (!strings.isEmpty()) {
            final ByteArrayOutputStream sb = new ByteArrayOutputStream();
            final DataOutputStream sdos = new DataOutputStream(sb);
            int offset = 0;
            for (String s : new ArrayList<>(strings.keySet())) {
                final byte[] utf = s.getBytes(StandardCharsets.UTF_8);
                strings.put(s, offset);
                sdos.writeShort(utf.length + 1);
                sdos.write(utf);
                sdos.writeByte(0);
                offset += 2 + utf.length + 1;
            }
            sdos.flush();
            dos.writeInt(STRING_MAGIC);
            dos.writeInt(sb.size());
            sb.writeTo(out);
        }

        final ByteArrayOutputStream db = new ByteArrayOutputStream();
        final DataOutputStream ddos = new DataOutputStream(db);
        // Garmin's DataBlock serializes containers breadth first: all sibling
        // headers precede their children. Recursive value emission corrupts a
        // batch containing two or more number rows (and nested maps).
        final Queue<Object> pending = new LinkedList<>(); // null is a valid value
        pending.add(root);
        while (!pending.isEmpty()) writeValue(ddos, pending.remove(), strings, pending);
        ddos.flush();
        dos.writeInt(DATA_MAGIC);
        dos.writeInt(db.size());
        db.writeTo(out);
        dos.flush();
        return out.toByteArray();
    }

    private static void collectStrings(Object o, LinkedHashMap<String,Integer> strings) {
        if (o == null) return;
        if (o instanceof String) {
            if (!strings.containsKey(o)) strings.put((String)o, 0);
            return;
        }
        if (o instanceof List<?>) {
            for (Object e : (List<?>)o) collectStrings(e, strings);
            return;
        }
        if (o instanceof Map<?,?>) {
            for (Map.Entry<?,?> e : ((Map<?,?>)o).entrySet()) {
                collectStrings(e.getKey(), strings);
                collectStrings(e.getValue(), strings);
            }
            return;
        }
        final Class<?> c = o.getClass();
        if (c.isArray() && !c.getComponentType().isPrimitive()) {
            final Object[] a = (Object[])o;
            for (Object e : a) collectStrings(e, strings);
        }
    }

    private static void writeValue(DataOutputStream out, Object o, Map<String,Integer> strings,
                                   Queue<Object> pending) throws IOException {
        if (o == null) {
            out.writeByte(0);
        } else if (o instanceof Boolean) {
            out.writeByte(9);
            out.writeByte(((Boolean)o) ? 1 : 0);
        } else if (o instanceof Float) {
            out.writeByte(2);
            out.writeInt(Float.floatToIntBits((Float)o));
        } else if (o instanceof Double) {
            out.writeByte(15);
            out.writeLong(Double.doubleToLongBits((Double)o));
        } else if (o instanceof Byte || o instanceof Short || o instanceof Integer || o instanceof Long) {
            final long v = ((Number)o).longValue();
            if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
                out.writeByte(1);
                out.writeInt((int)v);
            } else {
                out.writeByte(14);
                out.writeLong(v);
            }
        } else if (o instanceof String) {
            final Integer off = strings.get((String)o);
            if (off == null) throw new IOException("String missing from table");
            out.writeByte(3);
            out.writeInt(off);
        } else if (o instanceof List<?>) {
            final List<?> l = (List<?>)o;
            out.writeByte(5);
            out.writeInt(l.size());
            pending.addAll(l);
        } else if (o instanceof Map<?,?>) {
            final Map<?,?> m = (Map<?,?>)o;
            out.writeByte(11);
            out.writeInt(m.size());
            for (Map.Entry<?,?> e : m.entrySet()) {
                pending.add(e.getKey());
                pending.add(e.getValue());
            }
        } else if (o.getClass().isArray() && !o.getClass().getComponentType().isPrimitive()) {
            final Object[] a = (Object[])o;
            out.writeByte(5);
            out.writeInt(a.length);
            for (Object e : a) pending.add(e);
        } else if (o instanceof Number) {
            // Last-resort handling for uncommon Number subclasses used by app data.
            final double d = ((Number)o).doubleValue();
            final long l = ((Number)o).longValue();
            if (d == (double)l) {
                if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                    out.writeByte(1); out.writeInt((int)l);
                } else {
                    out.writeByte(14); out.writeLong(l);
                }
            } else {
                out.writeByte(15); out.writeLong(Double.doubleToLongBits(d));
            }
        } else {
            throw new IOException("Unsupported Connect IQ value type: " + o.getClass().getName());
        }
    }

    static Object decode(byte[] in) throws IOException {
        if (in == null || in.length < 9 || in.length > MAX_MESSAGE_BYTES)
            throw new IOException("Bad Connect IQ message size " + (in == null ? -1 : in.length));
        final DataInputStream dis = new DataInputStream(new java.io.ByteArrayInputStream(in));
        final Map<Integer,String> strings = new LinkedHashMap<>();

        int magic = dis.readInt();
        if (magic == STRING_MAGIC) {
            final int len = dis.readInt();
            if (len < 0 || len > dis.available() - 9) throw new IOException("Bad string section length " + len);
            final byte[] body = new byte[len];
            dis.readFully(body);
            int p = 0;
            while (p < body.length) {
                if (p + 2 > body.length) throw new EOFException("Short string length");
                final int slen = ((body[p] & 0xff) << 8) | (body[p+1] & 0xff);
                final int off = p;
                p += 2;
                if (slen <= 0 || p + slen > body.length) throw new IOException("Bad string size " + slen);
                final int textLen = body[p+slen-1] == 0 ? slen-1 : slen;
                strings.put(off, new String(body, p, textLen, StandardCharsets.UTF_8));
                p += slen;
            }
            magic = dis.readInt();
        }
        if (magic != DATA_MAGIC) throw new IOException(String.format("Bad data magic %08x", magic));
        final int dlen = dis.readInt();
        if (dlen < 1 || dlen != dis.available()) throw new IOException("Bad data section length " + dlen);
        final byte[] body = new byte[dlen];
        dis.readFully(body);
        final DataInputStream din = new DataInputStream(new java.io.ByteArrayInputStream(body));
        final ValueReader reader = new ValueReader(din, strings);
        final Object root = reader.read(0);
        while (!reader.pending.isEmpty()) {
            final ContainerValue container = reader.pending.remove();
            for (int i=0; i<container.childCount; i++)
                container.children.add(reader.read(container.depth+1));
        }
        if (reader.remainingValues != 0) throw new IOException("Missing Connect IQ values");
        if (din.available() != 0) throw new IOException("Trailing data bytes: " + din.available());
        return materialize(root);
    }

    private static final class ContainerValue {
        final boolean map;
        final int childCount, depth;
        final ArrayList<Object> children;
        ContainerValue(boolean map, int childCount, int depth) {
            this.map=map; this.childCount=childCount; this.depth=depth;
            children=new ArrayList<>(childCount);
        }
    }

    private static final class ValueReader {
        final DataInputStream in;
        final Map<Integer,String> strings;
        final Queue<ContainerValue> pending=new ArrayDeque<>();
        long remainingValues=1;
        ValueReader(DataInputStream in, Map<Integer,String> strings) {
            this.in=in; this.strings=strings;
        }
        Object read(int depth) throws IOException {
            if (depth > MAX_NESTING) throw new IOException("Connect IQ nesting too deep");
            if (--remainingValues < 0) throw new IOException("Unexpected Connect IQ value");
            final int type=in.readUnsignedByte();
            switch (type) {
                case 0: return null;
                case 1: return in.readInt();
                case 2: return Float.intBitsToFloat(in.readInt());
                case 3: {
                    final int off=in.readInt();
                    final String s=strings.get(off);
                    if (s==null) throw new IOException("Bad string offset " + off);
                    return s;
                }
                case 5:
                case 11: {
                    final int n=in.readInt();
                    final long children=(long)n*(type==11?2:1);
                    // Reserve all still-unread children across the queue before
                    // allocating. Individual length checks alone allow many
                    // sibling containers to claim the same remaining bytes.
                    if (n<0 || children+remainingValues>in.available())
                        throw new IOException("Bad container length " + n);
                    remainingValues+=children;
                    final ContainerValue value=new ContainerValue(type==11,(int)children,depth);
                    pending.add(value);
                    return value;
                }
                case 9: return in.readUnsignedByte()!=0;
                case 14: return in.readLong();
                case 15: return Double.longBitsToDouble(in.readLong());
                default: throw new IOException("Unsupported Connect IQ type " + type);
            }
        }
    }

    private static Object materialize(Object value) {
        if (!(value instanceof ContainerValue)) return value;
        final ContainerValue container=(ContainerValue)value;
        if (container.map) {
            final LinkedHashMap<Object,Object> out=new LinkedHashMap<>();
            // Materialize keys before inserting them so a nested collection key
            // cannot change its hash after insertion into the resulting map.
            for (int i=0;i<container.children.size();i+=2)
                out.put(materialize(container.children.get(i)),materialize(container.children.get(i+1)));
            return out;
        }
        final ArrayList<Object> out=new ArrayList<>(container.children.size());
        for (Object child : container.children) out.add(materialize(child));
        return out;
    }

    // Garmin's legacy transport prepends a trailing-padding count. Keep the
    // phone's established 1..4-byte encoding; watch messages can also use zero
    // when the count byte plus object already fill complete four-byte words.
    static byte[] encrypt(byte[] plain, byte[] key) {
        if (plain.length == 0) return new byte[0];
        int trailing = (4 - ((plain.length + 1) & 3)) & 3;
        if (trailing == 0) trailing = 4;
        final byte[] padded = new byte[plain.length + 1 + trailing];
        padded[0] = (byte)trailing;
        System.arraycopy(plain, 0, padded, 1, plain.length);
        final int[] v = toInts(padded, 0, padded.length);
        final int[] k = toInts(key, 0, key.length);
        final int n = v.length;
        int rounds = 6 + 52 / n;
        int sum = 0;
        int z = v[n-1];
        final int delta = 0x9E3779B9;
        while (rounds-- > 0) {
            sum += delta;
            final int e = (sum >>> 2) & 3;
            for (int p=0; p<n-1; p++) {
                final int y = v[p+1];
                z = v[p] += mx(sum, y, z, p, e, k);
            }
            final int y = v[0];
            z = v[n-1] += mx(sum, y, z, n-1, e, k);
        }
        return fromInts(v);
    }

    static byte[] decrypt(byte[] cipher, byte[] key) throws IOException {
        if (cipher == null || cipher.length < 8 || cipher.length > MAX_MESSAGE_BYTES || (cipher.length & 3) != 0)
            throw new IOException("Bad encrypted length " + (cipher == null ? -1 : cipher.length));
        if (key == null || key.length != 16) throw new IOException("XXTEA key must be 16 bytes");
        final int[] v = toInts(cipher, 0, cipher.length);
        final int[] k = toInts(key, 0, key.length);
        final int n = v.length;
        final int delta = 0x9E3779B9;
        int rounds = 6 + 52 / n;
        int sum = rounds * delta;
        int y = v[0];
        while (sum != 0) {
            final int e = (sum >>> 2) & 3;
            for (int p=n-1; p>0; p--) {
                final int z = v[p-1];
                y = v[p] -= mx(sum, y, z, p, e, k);
            }
            final int z = v[n-1];
            y = v[0] -= mx(sum, y, z, 0, e, k);
            sum -= delta;
        }
        final byte[] padded = fromInts(v);
        final int trailing = padded[0] & 0xff;
        final int len = padded.length - trailing - 1;
        if (trailing > 4 || len < 0) throw new IOException("Bad XXTEA padding " + trailing);
        // The VA3 capture contains valid START and HAVENUMS messages with nonzero
        // trailing padding. Only the count is defined; validate the decrypted
        // object framing in decode(), not the contents of those unused bytes.
        final byte[] out = new byte[len];
        System.arraycopy(padded, 1, out, 0, len);
        return out;
    }

    private static int mx(int sum, int y, int z, int p, int e, int[] k) {
        return ((z >>> 5 ^ y << 2) + (y >>> 3 ^ z << 4)) ^ ((sum ^ y) + (k[(p & 3) ^ e] ^ z));
    }

    private static int[] toInts(byte[] in, int off, int len) {
        final int[] out = new int[(len + 3) >>> 2];
        for (int i=0;i<len;i++) out[i>>>2] |= (in[off+i] & 0xff) << ((i & 3) << 3);
        return out;
    }

    private static byte[] fromInts(int[] in) {
        final byte[] out = new byte[in.length << 2];
        for (int i=0;i<out.length;i++) out[i] = (byte)(in[i>>>2] >>> ((i & 3) << 3));
        return out;
    }

    static int crc16Arc(byte[] data) {
        int crc = 0;
        for (byte b : data) {
            crc ^= b & 0xff;
            for (int i=0;i<8;i++) crc = ((crc & 1) != 0) ? ((crc >>> 1) ^ 0xA001) : (crc >>> 1);
        }
        return crc & 0xffff;
    }

    static byte[] hex(String s) {
        final int n = s.length();
        if ((n & 1) != 0) throw new IllegalArgumentException("Odd hex string");
        final byte[] out = new byte[n/2];
        for (int i=0;i<out.length;i++) out[i] = (byte)Integer.parseInt(s.substring(i*2,i*2+2),16);
        return out;
    }
}
