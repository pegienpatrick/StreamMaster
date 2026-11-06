package net.ossrs.yasea.demo.studio;

/** One place to stream to: a platform, its ingest server and your stream key. */
public final class Destination {

    /** Known platforms with their usual ingest servers. Always double-check in the platform's dashboard. */
    public enum Platform {
        TWITCH("Twitch", 0xFF9146FF,
            new String[]{"rtmps://ingest.global-contribute.live-video.net/app", "rtmp://live.twitch.tv/app"},
            "Twitch Creator Dashboard → Settings → Stream → Primary Stream Key"),
        YOUTUBE("YouTube", 0xFFFF0000,
            new String[]{"rtmps://a.rtmps.youtube.com:443/live2", "rtmp://a.rtmp.youtube.com/live2"},
            "YouTube Studio → Create → Go live → Stream → Stream key"),
        KICK("Kick", 0xFF53FC18,
            new String[]{},
            "Kick Dashboard → Stream URL and Stream Key. Paste the Stream URL into Server; it is specific to your account."),
        CUSTOM("Custom", 0xFF8A93A8,
            new String[]{},
            "Any RTMP/RTMPS server. Enter the full URL; the key is optional and is added to the end if you set one.");

        public final String label;
        public final int color;
        public final String[] servers;
        public final String keyHelp;

        Platform(String label, int color, String[] servers, String keyHelp) {
            this.label = label;
            this.color = color;
            this.servers = servers;
            this.keyHelp = keyHelp;
        }

        public static Platform of(String name) {
            try {
                return valueOf(name);
            } catch (IllegalArgumentException | NullPointerException e) {
                return CUSTOM;
            }
        }
    }

    public String id;
    public String name;
    public Platform platform = Platform.CUSTOM;
    public String server = "";
    public String key = "";

    /** The full publish URL, or null if it is not usable yet. */
    public String url() {
        String s = server == null ? "" : server.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (!(s.startsWith("rtmp://") || s.startsWith("rtmps://"))) {
            return null;
        }
        String k = key == null ? "" : key.trim();
        if (k.isEmpty()) {
            return platform == Platform.CUSTOM ? s : null; // the big platforms always need a key
        }
        return s + "/" + k;
    }

    /** "live_12••••ab" style display of the key. */
    public String maskedKey() {
        String k = key == null ? "" : key.trim();
        if (k.isEmpty()) {
            return "no key";
        }
        if (k.length() <= 6) {
            return "••••••";
        }
        return k.substring(0, 2) + "••••••" + k.substring(k.length() - 2);
    }

    public String host() {
        String s = server == null ? "" : server;
        int i = s.indexOf("://");
        if (i >= 0) {
            s = s.substring(i + 3);
        }
        int slash = s.indexOf('/');
        return slash >= 0 ? s.substring(0, slash) : s;
    }
}
