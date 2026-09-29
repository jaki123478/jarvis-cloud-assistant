package com.giarvis.app;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parser locale per promemoria naturali, senza rete. */
public final class ReminderParser {
    public static final class Result { public final long at; public final String text; Result(long at,String text){this.at=at;this.text=text;} }
    private ReminderParser() {}
    public static Result parse(String input, long now) {
        if(input==null)return null; String q=input.trim().toLowerCase(Locale.ROOT);
        Matcher relative=Pattern.compile("(?:ricordami|tra)\\s+(?:tra\\s+)?(\\d+|un|una)\\s*(minuto|minuti|ora|ore)\\b\\s*(.*)",Pattern.CASE_INSENSITIVE).matcher(q);
        if(relative.matches()){long n=(relative.group(1).startsWith("un")?1:Long.parseLong(relative.group(1)));long ms=(relative.group(2).startsWith("ora")?3600000L:60000L)*n;return new Result(now+ms,clean(relative.group(3)));}
        Matcher clock=Pattern.compile(".*?(domani|oggi)?\\s*(?:alle|ore)\\s*(\\d{1,2})(?::(\\d{2}))?\\s*(.*)",Pattern.CASE_INSENSITIVE).matcher(q);
        if(!clock.matches())return null;Calendar c=Calendar.getInstance();c.setTimeInMillis(now);if("domani".equals(clock.group(1)))c.add(Calendar.DAY_OF_YEAR,1);int h=Integer.parseInt(clock.group(2));int min=clock.group(3)==null?0:Integer.parseInt(clock.group(3));c.set(Calendar.HOUR_OF_DAY,h);c.set(Calendar.MINUTE,min);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);if(c.getTimeInMillis()<=now&&!"domani".equals(clock.group(1)))c.add(Calendar.DAY_OF_YEAR,1);return new Result(c.getTimeInMillis(),clean(clock.group(4)));
    }
    private static String clean(String s){if(s==null||s.trim().isEmpty())return "appuntamento";return s.replaceFirst("^(che|per|di)\\s+"," ").trim();}
}
