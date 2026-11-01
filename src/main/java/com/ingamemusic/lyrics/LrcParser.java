package com.ingamemusic.lyrics;

import java.util.*;
import java.util.regex.*;

public final class LrcParser {
	private static final Pattern TS = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]");
	private static final Pattern TAG = Pattern.compile("^\\[[A-Za-z]+:.*]$");

	/** Parses LRC text. Synced lyrics if any timestamps exist, plain lyrics otherwise, null if empty. */
	public static Lyrics parse(String raw) {
		if (raw == null || raw.isBlank()) return null;
		List<long[]> idx = new ArrayList<>(); // {time, textIndex}
		List<String> texts = new ArrayList<>();
		List<String> plain = new ArrayList<>();
		Matcher m = TS.matcher("");
		for (String line : raw.replace("\r", "").split("\n")) {
			String t = line.strip();
			int pos = 0; List<Long> times = new ArrayList<>();
			while (true) {
				m.reset(t); m.region(pos, t.length());
				if (!m.lookingAt()) break;
				long min = Long.parseLong(m.group(1)), sec = Long.parseLong(m.group(2)), ms = 0;
				String f = m.group(3);
				if (f != null) ms = f.length() == 1 ? Long.parseLong(f) * 100 : f.length() == 2 ? Long.parseLong(f) * 10 : Long.parseLong(f);
				times.add(min * 60000 + sec * 1000 + ms);
				pos = m.end();
			}
			String text = t.substring(pos).strip();
			if (times.isEmpty()) { if (!TAG.matcher(t).matches()) plain.add(t); continue; }
			for (long tm : times) idx.add(new long[]{tm, texts.size()});
			texts.add(text);
		}
		if (!idx.isEmpty()) {
			idx.sort(Comparator.comparingLong(a -> a[0]));
			long[] ts = new long[idx.size()]; String[] ls = new String[idx.size()];
			for (int i = 0; i < ts.length; i++) { ts[i] = idx.get(i)[0]; ls[i] = texts.get((int) idx.get(i)[1]); }
			return new Lyrics(true, ts, ls);
		}
		while (!plain.isEmpty() && plain.get(0).isEmpty()) plain.remove(0);
		if (plain.isEmpty()) return null;
		return new Lyrics(false, null, plain.toArray(new String[0]));
	}
}
