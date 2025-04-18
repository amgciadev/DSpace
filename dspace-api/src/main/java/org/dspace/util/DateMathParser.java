/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.util;

import java.text.ParseException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * This class (Apache license) is copied from Apache Solr, adding some tweaks to
 * resolve an unneeded dependency.  See
 * <a href='https://raw.githubusercontent.com/apache/lucene-solr/releases/lucene-solr/7.1.0/solr/core/src/java/org/apache/solr/util/DateMathParser.java'>the original</a>.
 *
 * <p>
 * A Simple Utility class for parsing "math" like strings relating to Dates.
 *
 * <p>
 * The basic syntax support addition, subtraction and rounding at various
 * levels of granularity (or "units").  Commands can be chained together
 * and are parsed from left to right.  '+' and '-' denote addition and
 * subtraction, while '/' denotes "round".  Round requires only a unit, while
 * addition/subtraction require an integer value and a unit.
 * Command strings must not include white space, but the "No-Op" command
 * (empty string) is allowed....
 * </p>
 *
 * <pre>
 *   /HOUR
 *      ... Round to the start of the current hour
 *   /DAY
 *      ... Round to the start of the current day
 *   +2YEARS
 *      ... Exactly two years in the future from now
 *   -1DAY
 *      ... Exactly 1 day prior to now
 *   /DAY+6MONTHS+3DAYS
 *      ... 6 months and 3 days in the future from the start of
 *          the current day
 *   +6MONTHS+3DAYS/DAY
 *      ... 6 months and 3 days in the future from now, rounded
 *          down to nearest day
 * </pre>
 *
 * <p>
 * (Multiple aliases exist for the various units of time (ie:
 * <code>MINUTE</code> and <code>MINUTES</code>; <code>MILLI</code>,
 * <code>MILLIS</code>, <code>MILLISECOND</code>, and
 * <code>MILLISECONDS</code>.)  The complete list can be found by
 * inspecting the keySet of {@link #CALENDAR_UNITS})
 * </p>
 *
 * <p>
 * All commands are relative to a "now" which is fixed in an instance of
 * DateMathParser such that
 * <code>p.parseMath("+0MILLISECOND").equals(p.parseMath("+0MILLISECOND"))</code>
 * no matter how many wall clock milliseconds elapse between the two
 * distinct calls to parse (Assuming no other thread calls
 * "<code>setNow</code>" in the interim).  The default value of 'now' is
 * the time at the moment the <code>DateMathParser</code> instance is
 * constructed, unless overridden by the <code>NOW</code>
 * request parameter.
 * </p>
 *
 * <p>
 * All commands are also affected to the rules of a specified {@link TimeZone}
 * (including the start/end of DST if any) which determine when each arbitrary
 * day starts.  This not only impacts rounding/adding of DAYs, but also
 * cascades to rounding of HOUR, MIN, MONTH, YEAR as well.  The default
 * <code>TimeZone</code> used is <code>UTC</code> unless  overridden by the
 * <code>TZ</code> request parameter.
 * </p>
 *
 * <p>
 * Historical dates:  The calendar computation is completely done with the
 * Gregorian system/algorithm.  It does <em>not</em> switch to Julian or
 * anything else, unlike the default {@link java.util.GregorianCalendar}.
 * </p>
 */
public class DateMathParser {

    private static final Logger LOG = LogManager.getLogger();

    public static final TimeZone UTC = TimeZone.getTimeZone(ZoneOffset.UTC);

    /**
     * Default TimeZone for DateMath rounding (UTC)
     */
    public static final TimeZone DEFAULT_MATH_TZ = UTC;

    /**
     * Differs by {@link DateTimeFormatter#ISO_INSTANT} in that it's lenient.
     *
     * @see #parseNoMath(String)
     */
    public static final DateTimeFormatter PARSER = new DateTimeFormatterBuilder()
        .parseCaseInsensitive().parseLenient().appendInstant().toFormatter(Locale.ROOT);

    /**
     * A mapping from (uppercased) String labels identifying time units,
     * to the corresponding {@link ChronoUnit} value (e.g. "YEARS") used to
     * set/add/roll that unit of measurement.
     *
     * <p>
     * A single logical unit of time might be represented by multiple labels
     * for convenience (i.e. <code>DATE==DAYS</code>,
     * <code>MILLI==MILLIS</code>)
     * </p>
     */
    public static final Map<String, ChronoUnit> CALENDAR_UNITS = makeUnitsMap();

    private static final String BAD_REQUEST = "[BAD REQUEST]";


    /**
     * @see #CALENDAR_UNITS
     */
    private static Map<String, ChronoUnit> makeUnitsMap() {

        // NOTE: consciously choosing not to support WEEK at this time,
        // because of complexity in rounding down to the nearest week
        // around a month/year boundary.
        // (Not to mention: it's not clear what people would *expect*)
        //
        // If we consider adding some time of "week" support, then
        // we probably need to change "Locale loc" to default to something
        // from a param via SolrRequestInfo as well.

        Map<String, ChronoUnit> units = new HashMap<>(13);
        units.put("YEAR", ChronoUnit.YEARS);
        units.put("YEARS", ChronoUnit.YEARS);
        units.put("MONTH", ChronoUnit.MONTHS);
        units.put("MONTHS", ChronoUnit.MONTHS);
        units.put("DAY", ChronoUnit.DAYS);
        units.put("DAYS", ChronoUnit.DAYS);
        units.put("DATE", ChronoUnit.DAYS);
        units.put("HOUR", ChronoUnit.HOURS);
        units.put("HOURS", ChronoUnit.HOURS);
        units.put("MINUTE", ChronoUnit.MINUTES);
        units.put("MINUTES", ChronoUnit.MINUTES);
        units.put("SECOND", ChronoUnit.SECONDS);
        units.put("SECONDS", ChronoUnit.SECONDS);
        units.put("MILLI", ChronoUnit.MILLIS);
        units.put("MILLIS", ChronoUnit.MILLIS);
        units.put("MILLISECOND", ChronoUnit.MILLIS);
        units.put("MILLISECONDS", ChronoUnit.MILLIS);

        // NOTE: Maybe eventually support NANOS

        return units;
    }

    /**
     * Returns a modified time by "adding" the specified value of units
     *
     * @throws IllegalArgumentException if unit isn't recognized.
     * @see #CALENDAR_UNITS
     */
    private static LocalDateTime add(LocalDateTime t, int val, String unit) {
        ChronoUnit uu = CALENDAR_UNITS.get(unit);
        if (null == uu) {
            throw new IllegalArgumentException("Adding Unit not recognized: "
                                                   + unit);
        }
        return t.plus(val, uu);
    }

    /**
     * Returns a modified time by "rounding" down to the specified unit
     *
     * @throws IllegalArgumentException if unit isn't recognized.
     * @see #CALENDAR_UNITS
     */
    private static LocalDateTime round(LocalDateTime t, String unit) {
        ChronoUnit uu = CALENDAR_UNITS.get(unit);
        if (null == uu) {
            throw new IllegalArgumentException("Rounding Unit not recognized: "
                                                   + unit);
        }
        // note: OffsetDateTime.truncatedTo does not support >= DAYS units so we handle those
        switch (uu) {
            case YEARS:
                return LocalDateTime.of(LocalDate.of(t.getYear(), 1, 1), LocalTime.MIDNIGHT); // midnight is 00:00:00
            case MONTHS:
                return LocalDateTime.of(LocalDate.of(t.getYear(), t.getMonth(), 1), LocalTime.MIDNIGHT);
            case DAYS:
                return LocalDateTime.of(t.toLocalDate(), LocalTime.MIDNIGHT);
            default:
                assert !uu.isDateBased();// >= DAY
                return t.truncatedTo(uu);
        }
    }

    /**
     * Parses a String which may be a date (in the standard ISO-8601 format)
     * followed by an optional math expression.
     *
     * @param now an optional fixed date to use as "NOW"
     * @param val the string to parse
     * @return result of applying the parsed expression to "NOW".
     * @throws Exception
     */
    public static LocalDateTime parseMath(LocalDateTime now, String val) throws Exception {
        String math;
        final DateMathParser p = new DateMathParser();

        if (null != now) {
            p.setNow(now);
        }

        if (val.startsWith("NOW")) {
            math = val.substring("NOW".length());
        } else {
            final int zz = val.indexOf('Z');
            if (zz == -1) {
                throw new Exception(BAD_REQUEST +
                                        "Invalid Date String:'" + val + '\'');
            }
            math = val.substring(zz + 1);
            try {
                p.setNow(parseNoMath(val.substring(0, zz + 1)));
            } catch (DateTimeParseException e) {
                throw new Exception(BAD_REQUEST +
                                        "Invalid Date in Date Math String:'" + val + '\'', e);
            }
        }

        if (null == math || math.equals("")) {
            return p.getNow();
        }

        try {
            return p.parseMath(math);
        } catch (ParseException e) {
            throw new Exception(BAD_REQUEST +
                                    "Invalid Date Math String:'" + val + '\'', e);
        }
    }

    /**
     * Parsing Solr dates <b>without DateMath</b>.
     * This is the standard/pervasive ISO-8601 UTC format but is configured with some leniency.
     *
     * Callers should almost always call {@link #parseMath(LocalDateTime, String)} instead.
     *
     * @throws DateTimeParseException if it can't parse
     */
    private static LocalDateTime parseNoMath(String val) {
        return PARSER.parse(val, LocalDateTime::from);
    }

    private TimeZone zone;
    private Locale loc;
    private LocalDateTime now;

    /**
     * Default constructor that assumes UTC should be used for rounding unless
     * otherwise specified in the SolrRequestInfo
     */
    public DateMathParser() {
        this(null);
    }

    /**
     * @param tz The TimeZone used for rounding (to determine when hours/days begin).  If null, then this method
     *           defaults
     *           to the value dictated by the SolrRequestInfo if it exists -- otherwise it uses UTC.
     * @see #DEFAULT_MATH_TZ
     */
    public DateMathParser(TimeZone tz) {
        zone = (null != tz) ? tz : DEFAULT_MATH_TZ;
    }

    /**
     * @return the time zone
     */
    public TimeZone getTimeZone() {
        return this.zone;
    }

    /**
     * Defines this instance's concept of "now".
     *
     * @param n new value of "now".
     * @see #getNow
     */
    public void setNow(LocalDateTime n) {
        now = n;
    }

    /**
     * Returns a clone of this instance's concept of "now" (never null).
     * If setNow was never called (or if null was specified) then this method
     * first defines 'now' as the value dictated by the SolrRequestInfo if it
     * exists -- otherwise it uses a new Date instance at the moment getNow()
     * is first called.
     *
     * @return "now".
     * @see #setNow
     */
    public LocalDateTime getNow() {
        if (now == null) {
            // fall back to current time if no request info set
            now = LocalDateTime.now(zone.toZoneId());
        }
        return now;
    }

    /**
     * Parses a date expression relative to "now".
     *
     * @param math a date expression such as "+24MONTHS".
     * @return the result of applying the expression to the current time.
     * @throws ParseException positions in ParseExceptions are token positions,
     *          not character positions.
     */
    public LocalDateTime parseMath(String math) throws ParseException {
        /* check for No-Op */
        if (0 == math.length()) {
            return getNow();
        }

        LOG.debug("parsing {}", math);

        ZoneId zoneId = zone.toZoneId();
        // localDateTime is a date and time local to the timezone specified
        LocalDateTime localDateTime = ZonedDateTime.of(getNow(), zoneId).toLocalDateTime();

        String[] ops = splitter.split(math);
        int pos = 0;
        while (pos < ops.length) {

            if (1 != ops[pos].length()) {
                throw new ParseException("Multi character command found: \"" + ops[pos] + "\"", pos);
            }
            char command = ops[pos++].charAt(0);

            switch (command) {
                case '/':
                    if (ops.length < pos + 1) {
                        throw new ParseException("Need a unit after command: \"" + command + "\"", pos);
                    }
                    try {
                        localDateTime = round(localDateTime, ops[pos++]);
                    } catch (IllegalArgumentException e) {
                        throw new ParseException("Unit not recognized: \"" + ops[pos - 1] + "\"", pos - 1);
                    }
                    break;
                case '+': /* fall through */
                case '-':
                    if (ops.length < pos + 2) {
                        throw new ParseException("Need a value and unit for command: \"" + command + "\"", pos);
                    }
                    int val = 0;
                    try {
                        val = Integer.parseInt(ops[pos++]);
                    } catch (NumberFormatException e) {
                        throw new ParseException("Not a Number: \"" + ops[pos - 1] + "\"", pos - 1);
                    }
                    if ('-' == command) {
                        val = 0 - val;
                    }
                    try {
                        String unit = ops[pos++];
                        localDateTime = add(localDateTime, val, unit);
                    } catch (IllegalArgumentException e) {
                        throw new ParseException("Unit not recognized: \"" + ops[pos - 1] + "\"", pos - 1);
                    }
                    break;
                default:
                    throw new ParseException("Unrecognized command: \"" + command + "\"", pos - 1);
            }
        }

        LOG.debug("returning {}", localDateTime);
        return ZonedDateTime.of(localDateTime, zoneId).toLocalDateTime();
    }

    private static Pattern splitter = Pattern.compile("\\b|(?<=\\d)(?=\\D)");

    /**
     * For manual testing.  With one argument, test one-argument parseMath.
     * With two (or more) arguments, test two-argument parseMath.
     *
     * @param argv date math expressions.
     * @throws java.lang.Exception passed through.
     */
    public static void main(String[] argv)
            throws Exception {
        DateMathParser parser = new DateMathParser();
        try {
            LocalDateTime parsed;

            if (argv.length <= 0) {
                System.err.println("Date math expression(s) expected.");
            }

            if (argv.length > 0) {
                parsed = parser.parseMath(argv[0]);
                System.out.format("Applied %s to implicit current time:  %s%n",
                        argv[0], parsed.toString());
            }

            if (argv.length > 1) {
                parsed = DateMathParser.parseMath(LocalDateTime.now(ZoneOffset.UTC), argv[1]);
                System.out.format("Applied %s to explicit current time:  %s%n",
                        argv[1], parsed.toString());
            }
        } catch (ParseException ex) {
            System.err.format("Oops:  %s%n", ex.getMessage());
        }
    }
}


