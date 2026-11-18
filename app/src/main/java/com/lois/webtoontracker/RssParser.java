package com.lois.webtoontracker;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lit le flux RSS d'une série webtoons.com et renvoie le dernier épisode publié. Java pur. */
public final class RssParser {

    private RssParser() {}

    public static final class Latest {
        public final long episodeNo;
        public final String title;
        public final String link;

        Latest(long episodeNo, String title, String link) {
            this.episodeNo = episodeNo;
            this.title = title;
            this.link = link;
        }
    }

    private static final Pattern ITEM = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL);
    private static final Pattern LINK = Pattern.compile("<link>(.*?)</link>", Pattern.DOTALL);
    private static final Pattern EP = Pattern.compile("episode_no=(\\d+)");

    public static Latest latest(String xml) {
        Latest best = null;
        Matcher items = ITEM.matcher(xml);
        while (items.find()) {
            String item = items.group(1);
            Matcher l = LINK.matcher(item);
            if (!l.find()) continue;
            String link = clean(l.group(1));
            Matcher e = EP.matcher(link);
            if (!e.find()) continue;
            long ep = Long.parseLong(e.group(1));
            if (best == null || ep > best.episodeNo) {
                Matcher t = TITLE.matcher(item);
                best = new Latest(ep, t.find() ? clean(t.group(1)) : "Épisode " + ep, link);
            }
        }
        return best;
    }

    private static String clean(String s) {
        return s.replace("<![CDATA[", "").replace("]]>", "")
                .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").trim();
    }
}
