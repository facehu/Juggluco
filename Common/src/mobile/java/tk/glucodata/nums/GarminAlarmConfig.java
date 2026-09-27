package tk.glucodata.nums;

import java.util.ArrayList;
import java.util.List;

/** Independent watch alarms. Thresholds use mg/L, as Juggluco's native settings do. */
public final class GarminAlarmConfig {
    public static final int SET=243, ACK=244, CAPABILITY=8, VERSION=3;
    // START capabilities: v2 record support is separate from actual tone hardware.
    public static final int SOUND_CONFIG_CAPABILITY=16, TONE_CAPABILITY=32;
    public static final int OUTPUT_CONFIG_CAPABILITY=64, VIBRATION_CAPABILITY=128;
    // Toybox.Attention.TONE_ALERT_LO / TONE_ALERT_HI / TONE_TIME_ALERT.
    public static final int DEFAULT_LOW_TONE=5, DEFAULT_HIGH_TONE=4, DEFAULT_LOSS_TONE=12;
    public final int revision, flags, low, high, lossMinutes;
    public final int lowRepeat, highRepeat, lossRepeat, lowDuration, highDuration, lossDuration;
    public final int soundFlags, lowTone, highTone, lossTone;
    public final int vibrationFlags;

    public GarminAlarmConfig(int revision,int flags,int low,int high,int lossMinutes,
            int lowRepeat,int highRepeat,int lossRepeat,int lowDuration,int highDuration,int lossDuration) {
        this(revision,flags,low,high,lossMinutes,lowRepeat,highRepeat,lossRepeat,
                lowDuration,highDuration,lossDuration,0,DEFAULT_LOW_TONE,DEFAULT_HIGH_TONE,DEFAULT_LOSS_TONE);
    }

    public GarminAlarmConfig(int revision,int flags,int low,int high,int lossMinutes,
            int lowRepeat,int highRepeat,int lossRepeat,int lowDuration,int highDuration,int lossDuration,
            int soundFlags,int lowTone,int highTone,int lossTone) {
        // v1/v2 always vibrated. Preserve that choice when loading old records.
        this(revision,flags,low,high,lossMinutes,lowRepeat,highRepeat,lossRepeat,
                lowDuration,highDuration,lossDuration,soundFlags,lowTone,highTone,lossTone,7);
    }

    public GarminAlarmConfig(int revision,int flags,int low,int high,int lossMinutes,
            int lowRepeat,int highRepeat,int lossRepeat,int lowDuration,int highDuration,int lossDuration,
            int soundFlags,int lowTone,int highTone,int lossTone,int vibrationFlags) {
        this.revision=revision; this.flags=flags; this.low=low; this.high=high;
        this.lossMinutes=lossMinutes; this.lowRepeat=lowRepeat; this.highRepeat=highRepeat;
        this.lossRepeat=lossRepeat; this.lowDuration=lowDuration; this.highDuration=highDuration;
        this.lossDuration=lossDuration;
        this.soundFlags=soundFlags; this.lowTone=lowTone; this.highTone=highTone; this.lossTone=lossTone;
        this.vibrationFlags=vibrationFlags;
        if(revision<1 || flags<0 || flags>7 || low<1 || low>10000 || high<1 || high>10000 ||
                ((flags&3)==3 && low>=high) || lossMinutes<1 || lossMinutes>4095 ||
                lowRepeat<0 || lowRepeat>4095 || highRepeat<0 || highRepeat>4095 ||
                lossRepeat<0 || lossRepeat>4095 || lowDuration<0 || lowDuration>65535 ||
                highDuration<0 || highDuration>65535 || lossDuration<0 || lossDuration>65535 ||
                soundFlags<0 || soundFlags>7 || lowTone<0 || lowTone>18 ||
                highTone<0 || highTone>18 || lossTone<0 || lossTone>18 ||
                vibrationFlags<0 || vibrationFlags>7)
            throw new IllegalArgumentException("Invalid watch alarm settings");
    }

    public List<Object> message() {
        return message(VERSION);
    }

    public List<Object> message(boolean soundConfigSupported) {
        return message(soundConfigSupported?2:1);
    }

    public List<Object> message(int version) {
        if(version<1 || version>VERSION) throw new IllegalArgumentException("Unknown watch alarm version");
        ArrayList<Object> m=new ArrayList<>(version==1?13:version==2?17:18);
        for(int v:new int[]{SET,version,revision,flags,low,high,lossMinutes,
                lowRepeat,highRepeat,lossRepeat,lowDuration,highDuration,lossDuration}) m.add(v);
        if(version>=2) for(int v:new int[]{soundFlags,lowTone,highTone,lossTone}) m.add(v);
        if(version>=3) m.add(vibrationFlags);
        return m;
    }

    public String encode() {
        StringBuilder b=new StringBuilder();
        for(Object v:message()) { if(b.length()!=0) b.append(','); b.append(v); }
        return b.toString();
    }

    public static GarminAlarmConfig decode(String text) {
        if(text==null) return null;
        try {
            String[] s=text.split(",",-1);
            if(s.length!=13 && s.length!=17 && s.length!=18) return null;
            int[] n=new int[s.length];
            for(int i=0;i<n.length;i++) n[i]=Integer.parseInt(s[i]);
            if(n[0]!=SET || (n.length==13?n[1]!=1:n.length==17?n[1]!=2:n[1]!=VERSION)) return null;
            if(n.length>=17) return new GarminAlarmConfig(n[2],n[3],n[4],n[5],n[6],n[7],n[8],n[9],
                    n[10],n[11],n[12],n[13],n[14],n[15],n[16],n.length==18?n[17]:7);
            return new GarminAlarmConfig(n[2],n[3],n[4],n[5],n[6],n[7],n[8],n[9],n[10],n[11],n[12]);
        } catch(IllegalArgumentException e) { return null; }
    }

    public GarminAlarmConfig withRevision(int revision) {
        return new GarminAlarmConfig(revision,flags,low,high,lossMinutes,
                lowRepeat,highRepeat,lossRepeat,lowDuration,highDuration,lossDuration,
                soundFlags,lowTone,highTone,lossTone,vibrationFlags);
    }

    public static int threshold(double value,boolean mmol) {
        double n=value*(mmol?180.0:10.0);
        if(Double.isNaN(n) || Double.isInfinite(n) || n<1 || n>10000)
            throw new IllegalArgumentException("Invalid glucose threshold");
        return (int)Math.round(n);
    }

    public static boolean replyMatches(Object sent,Object received) {
        if(!(sent instanceof List<?>) || !(received instanceof List<?>)) return false;
        List<?> s=(List<?>)sent,r=(List<?>)received;
        return ((s.size()==13 && Integer.valueOf(1).equals(s.get(1))) ||
                (s.size()==17 && Integer.valueOf(2).equals(s.get(1))) ||
                (s.size()==18 && Integer.valueOf(VERSION).equals(s.get(1)))) &&
                r.size()==4 && Integer.valueOf(SET).equals(s.get(0)) &&
                Integer.valueOf(ACK).equals(r.get(0)) && s.get(2).equals(r.get(1)) &&
                r.get(2) instanceof Integer && (Integer)r.get(2)>=0 && (Integer)r.get(2)<=3 &&
                r.get(3) instanceof Integer && (Integer)r.get(3)>=0;
    }
}
